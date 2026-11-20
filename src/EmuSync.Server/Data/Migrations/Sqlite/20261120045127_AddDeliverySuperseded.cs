using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace EmuSync.Server.Data.Migrations.Sqlite
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
                type: "INTEGER",
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
