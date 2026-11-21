using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tandem.Server.Data.Migrations.Sqlite
{
    /// <inheritdoc />
    public partial class AddDeliveryDetail : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "Detail",
                table: "Deliveries",
                type: "TEXT",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "Detail",
                table: "Deliveries");
        }
    }
}
