using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace EmuSync.Server.Data.Migrations.Postgres
{
    /// <inheritdoc />
    public partial class AddDeliverySuperseded : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "Superseded",
                table: "Deliveries",
                type: "boolean",
                nullable: false,
                defaultValue: false);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "Superseded",
                table: "Deliveries");
        }
    }
}
